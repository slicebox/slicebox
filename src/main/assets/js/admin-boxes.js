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

.controller('AdminBoxesCtrl', function($scope, $http, $interval, $mdDialog) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: $scope.confirmDeleteEntitiesFunction('/api/boxes/', 'box(es)')
            }
        ];

    $scope.callbacks = {};

    var timer = $interval(function() {
        if (angular.isDefined($scope.callbacks.boxesTable)) {
            $scope.callbacks.boxesTable.reloadPage();
        }
    }, 5000);

    $scope.$on('$destroy', function() {
        $interval.cancel(timer);
    });
  
    // Scope functions
    $scope.loadBoxesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/boxes');
    };

    $scope.addBoxButtonClicked = function() {
        var dialogPromise = $mdDialog.show({
            templateUrl: '/assets/partials/addBoxModalContent.html',
            controller: 'AddBoxModalCtrl',
            scope: $scope.$new()
        });
        dialogPromise.then(function (response) {
            $scope.showInfoMessage("Box added");                
            $scope.callbacks.boxesTable.reloadPage();
        });
    };
})

.controller('AddBoxModalCtrl', function($scope, $mdDialog, $http) {

    // Scope functions
    $scope.radioButtonChanged = function() {
        $scope.addBoxForm.$setPristine();
    };

    $scope.generateURLButtonClicked = function() {
        if ($scope.addBoxForm.$invalid) {
            return;
        }

        var generateURLPromise = $http.post('/api/boxes/createconnection', {value: $scope.uiState.remoteBoxName});

        generateURLPromise.success(function(data) {
            showBaseURLDialog(data.baseUrl);
            $mdDialog.hide();
        });

        generateURLPromise.error(function(data) {
            $scope.showErrorMessage(data);                
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

        connectPromise.success(function(data) {
            $mdDialog.hide();
        });

        connectPromise.error(function(data) {
            $scope.showErrorMessage(data);
        });

        return connectPromise;
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };

    // Private functions
    function showBaseURLDialog(baseURL) {
        $mdDialog.show({
                templateUrl: '/assets/partials/baseURLModalContent.html',
                controller: 'BaseURLModalCtrl',
                locals: {
                    baseURL: baseURL
                }
            });
    }
})

.controller('BaseURLModalCtrl', function($scope, $mdDialog, baseURL) {
    // Initialization
    $scope.baseURL = baseURL;

    // Scope functions
    $scope.mailBody = function() {
        var bodyText = 'Box connection URL:\n\n' + baseURL;

        return encodeURIComponent(bodyText);
    };

    $scope.closeButtonClicked = function() {
        $mdDialog.cancel();
    };
});