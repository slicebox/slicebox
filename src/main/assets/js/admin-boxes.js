(function () {
   'use strict';
}());

angular.module('slicebox.adminBoxes', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/admin/boxes', {
    templateUrl: '/assets/partials/adminBoxes.html',
    controller: 'AdminBoxesCtrl'
  });
})

.controller('AdminBoxesCtrl', function($scope, $http, $interval, $mdDialog, sbxToast, openDeleteEntitiesModalFunction) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/boxes/', 'box(es)')
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.boxesTable)) {
            $scope.callbacks.boxesTable.reloadPage();
        }
    }, 15000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });

    // Scope functions
    $scope.loadBoxesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/boxes?startindex=' + startIndex + '&count=' + count);
    };

    $scope.addBoxButtonClicked = function() {
        var dialogPromise = $mdDialog.show({
            templateUrl: '/assets/partials/addBoxModalContent.html',
            controller: 'AddBoxModalCtrl',
            scope: $scope.$new()
        });
        dialogPromise.then(function (response) {
            sbxToast.showInfoMessage("Box added");                
            $scope.callbacks.boxesTable.reloadPage();
        });
    };

})

.controller('AddBoxModalCtrl', function($scope, $mdDialog, $http, sbxToast) {

    $scope.uiState = {
        addChoice: '',
        remoteBoxName: "",
        connectionURL: ""
    };

    // Scope functions
    $scope.radioButtonChanged = function() {
        $scope.addBoxForm.$setPristine();
    };

    $scope.generateURLButtonClicked = function() {
        if ($scope.addBoxForm.$invalid) {
            return;
        }

        var connectionData = {
            name: $scope.uiState.remoteBoxName
        };

        var generateURLPromise = $http.post('/api/boxes/createconnection', connectionData);

        generateURLPromise.success(function(box) {
            showBaseURLDialog(box);
            $mdDialog.hide();
        });

        generateURLPromise.error(function(reason) {
            sbxToast.showErrorMessage(reason);                
        });

        return generateURLPromise;
    };

    $scope.connectButtonClicked = function() {
        if ($scope.addBoxForm.$invalid) {
            return;
        }

        $scope.uiState.errorMessage = null;

        var connectPromise = $http.post('/api/boxes/connect',
            {
                name: $scope.uiState.remoteBoxName,
                baseUrl: $scope.uiState.connectionURL
            });

        connectPromise.success(function(box) {
            $mdDialog.hide();
        });

        connectPromise.error(function(reason) {
            sbxToast.showErrorMessage(reason);
        });

        return connectPromise;
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

    // Private functions
    function showBaseURLDialog(box) {
        $mdDialog.show({
                templateUrl: '/assets/partials/baseURLModalContent.html',
                controller: 'BaseURLModalCtrl',
                locals: {
                    box: box
                }
        });
    }
})

.controller('BaseURLModalCtrl', function($scope, $mdDialog, box) {
    // Initialization
    $scope.name = box.name;
    $scope.baseURL = box.baseUrl;

    // Scope functions
    $scope.mailBody = function() {
        var bodyText = 'Box connection URL:\n\n' + $scope.baseURL;

        return encodeURIComponent(bodyText);
    };

    $scope.closeButtonClicked = function() {
        $mdDialog.cancel();
    };
});