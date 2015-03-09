(function () {
   'use strict';
}());

angular.module('slicebox.outbox', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/outbox', {
    templateUrl: '/assets/partials/outbox.html',
    controller: 'OutboxCtrl'
  });
})

.controller('OutboxCtrl', function($scope, $http, $modal, $q, $interval, openConfirmationDeleteModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteOutboxEntries
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.outboxTable)) {
            $scope.callbacks.outboxTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadOutboxPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/outbox');
    };

    $scope.convertOutboxPageData = function(outboxEntries) {
        var outboxDataCollector = {};
        var outboxTransactionData;
        var imagesLeft;
        var pageData = [];

        angular.forEach(outboxEntries, function(outboxEntry) {
            outboxTransactionData = outboxDataCollector[outboxEntry.transactionId];
            if (angular.isUndefined(outboxTransactionData)) {
                outboxTransactionData =
                    {
                        id: outboxEntry.id, 
                        remoteBoxName: outboxEntry.remoteBoxName,
                        totalImageCount: outboxEntry.totalImageCount,
                        failed: outboxEntry.failed,
                        imagesLeft: 0
                    };

                outboxDataCollector[outboxEntry.transactionId] = outboxTransactionData;
            }

            outboxTransactionData.imagesLeft = outboxTransactionData.imagesLeft + 1;
        });

        angular.forEach(outboxDataCollector, function(outboxTransactionData) {
            pageData.push(outboxTransactionData);
        });

        return pageData;
    };

    // Private functions
    function confirmDeleteOutboxEntries(outboxEntries) {
        var deleteConfirmationText = 'Permanently delete ' + outboxEntries.length + ' outbox entries?';

        return openConfirmationDeleteModal('Delete Outbox Entries', deleteConfirmationText, function() {
            return deleteOutboxEntries(outboxEntries);
        });
    }

    function deleteOutboxEntries(outboxEntries) {
        var deletePromises = [];
        var deletePromise;
        var deleteAllPromies;

        angular.forEach(outboxEntries, function(outboxEntry) {
            deletePromise = $http.delete('/api/outbox/' + outboxEntry.id);
            deletePromises.push(deletePromise);
        });

        deleteAllPromies = $q.all(deletePromises);

        deleteAllPromies.then(null, function(response) {
            $scope.appendErrorMessage(response.data);
        });

        return deleteAllPromies;
    }
});